(function () {
    'use strict';
}());

// Declare app level module which depends on views, and components
angular.module('slicebox', [
    'ngRoute',
    'ngCookies',
    'ngAnimate',
    'ngMaterial',
    'slicebox.utils',
    'slicebox.directives',
    'slicebox.login',
    'slicebox.import',
    'slicebox.home',
    'slicebox.anonymization',
    'slicebox.transactions',
    'slicebox.log',
    'slicebox.adminPacs',
    'slicebox.adminWatchDirectories',
    'slicebox.adminBoxes',
    'slicebox.adminUsers',
    'slicebox.adminSeriesTypes'
])

.config(function($locationProvider, $routeProvider, $mdThemingProvider, $animateProvider, $filterProvider) {
    $locationProvider.html5Mode(true);
    $routeProvider.otherwise({redirectTo: '/'});

    $mdThemingProvider.theme('default')
        .primaryPalette('blue-grey', {
            'default': '600',
        })
        .accentPalette('pink')
        .warnPalette('red');

    $mdThemingProvider.theme('redTheme')
        .primaryPalette('red');

    // prevent ng-animate on spinners, causes weird behaviour with ng-if/ng-show
    $animateProvider.classNameFilter(/^((?!(fa-spinner)).)*$/);

    // Register filters
    $filterProvider.register('hexvalue', function() {
        return function(intValue, length) {
            var returnValue = intValue;

            if (!length) {
                length = 4;
            }

            if (angular.isDefined(intValue) && angular.isNumber(intValue) && intValue !== 0) {
                returnValue = intValue.toString(16);

                while (returnValue.length < length) {
                    returnValue = '0' + returnValue;
                }

                returnValue = returnValue.toUpperCase();
            }

            return returnValue;
        };
    });
})

.filter('prettyPatientName', function () {
    return function (text) {
        return text ? text.replace(new RegExp('\\^', 'g'), ' ') : '';
    };
})

.controller('SliceboxCtrl', function($scope, $http, $q, $location, $mdSidenav, $mdToast, $mdDialog, authenticationService, openConfirmActionModal) {

    $scope.uiState = {
        showMenu: true,
        addEntityInProgress: false
    };

    $scope.toggleLeftNav = function() {
        $mdSidenav('leftNav').toggle();
    };

    $scope.logout = function() {
        authenticationService.clearCredentials();
        $scope.uiState.showMenu = false;
        $location.url("/login");
    };

    $scope.userSignedIn = function() {
        return authenticationService.userSignedIn();
    };

    $scope.isCurrentPath = function(path) { 
        return $location.path() === path;
    };

    $scope.currentPathStartsWith = function(path) { 
        return $location.path().indexOf(path) === 0;
    };

    $scope.showErrorMessage = function(errorMessage) {
        var toast = $mdToast.simple()
            .content(errorMessage)
            .action('Dismiss')
            .highlightAction(true)
            .hideDelay(30000)
            .theme('redTheme')
            .position("bottom right");
        $mdToast.show(toast);
    };

    $scope.showInfoMessage = function(infoMessage) {
        var toast = {
            parent: angular.element(document.getElementById("content")),
            template: '<md-toast>' + infoMessage + '</md-toast>',
            position: 'top right'
        };
        $mdToast.show(toast);
    };

    $scope.addEntityButtonClicked = function(modalContentName, controllerName, url, entityName, table) {
        var dialogPromise = $mdDialog.show({
            templateUrl: '/assets/partials/' + modalContentName,
            controller: controllerName
        });

        dialogPromise.then(function (entity) {
            $scope.uiState.addEntityInProgress = true;

            var addPromise = $http.post(url, entity);
            addPromise.error(function(data) {
                $scope.showErrorMessage(data);
            });

            addPromise.success(function() {
                $scope.showInfoMessage(entityName + " added");                
            });

            addPromise.finally(function() {
                $scope.uiState.addEntityInProgress = false;
                table.reloadPage();
            });
        });
    };

    $scope.confirmDeleteEntitiesFunction = function(url, entitiesText) {

        return function(entities) {
            var deleteConfirmationText = 'Permanently delete ' + entities.length + ' ' + entitiesText + '?';

            return openConfirmActionModal('Delete ' + entitiesText, deleteConfirmationText, 'Delete', function() {
                return deleteEntities(url, entities, entitiesText);
            });
        };
    };

    // private functions
    function deleteEntities(url, entities, entitiesText) {

        var removePromises = [];
        var removePromise;
        var deleteAllPromises;

        angular.forEach(entities, function(entity) {
            removePromise = $http.delete(url + entity.id);
            removePromises.push(removePromise);
        });

        deleteAllPromises = $q.all(removePromises);

        deleteAllPromises.then(function() {
            $scope.showInfoMessage(entities.length + " " + entitiesText + " deleted");
        }, function(response) {
            $scope.showErrorMessage(response.data);
        });

        return deleteAllPromises;
    }    
})

.run(function ($rootScope, $location, $cookieStore, $http) {
    // keep user logged in after page refresh
    $rootScope.globals = $cookieStore.get('globals') || {};
    if ($rootScope.globals.currentUser) {
        $http.defaults.headers.common['Authorization'] = 'Basic ' + $rootScope.globals.currentUser.authdata; // jshint ignore:line
    }

    $rootScope.$on('$locationChangeStart', function (event, next, current) {
        // redirect to login page if not logged in
        if ($location.path() !== '/login' && !$rootScope.globals.currentUser) {
            $location.path('/login');
        }
    });
});
